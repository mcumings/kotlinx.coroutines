/*
 * Copyright 2016-2022 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

import org.gradle.api.*
import org.gradle.api.attributes.*
import org.gradle.api.tasks.bundling.*
import org.gradle.api.tasks.compile.*
import org.gradle.jvm.toolchain.*
import org.gradle.kotlin.dsl.*
import org.jetbrains.kotlin.gradle.dsl.*

object Java9Modularity {

    @JvmStatic
    fun Project.configure() {
        val javaToolchains = extensions.getByName("javaToolchains") as JavaToolchainService
        val target = when (val kotlin = extensions.getByName("kotlin")) {
            is KotlinJvmProjectExtension -> kotlin.target
            is KotlinMultiplatformExtension -> kotlin.targets.getByName("jvm")
            else -> throw IllegalStateException("Unknown Kotlin project extension in $project")
        }
        val compilation = target.compilations.getByName("main")

        // Force the use of JARs for compile dependencies, so any JPMS descriptors are picked up.
        // For more details, see https://github.com/gradle/gradle/issues/890#issuecomment-623392772
        configurations.getByName(compilation.compileDependencyConfigurationName).attributes {
            attribute(
                LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
                objects.named(LibraryElements::class, LibraryElements.JAR)
            )
        }

        val compileJavaModuleInfo = tasks.register("compileJavaModuleInfo", JavaCompile::class.java) {
            val moduleName = project.name.replace('-', '.') // this module's name
            val sourceFile = file("${target.name.ifEmpty { "." }}/java9/module-info.java")
            if (!sourceFile.exists()) {
                throw IllegalStateException("$sourceFile not found in $project")
            }
            val compileKotlinTask = compilation.compileKotlinTask as org.jetbrains.kotlin.gradle.tasks.KotlinCompile
            val targetDir = compileKotlinTask.destinationDirectory.dir("../java9")

            // Use a Java 11 compiler for the module-info.
            javaCompiler.set(javaToolchains.compilerFor {
                languageVersion.set(JavaLanguageVersion.of(11))
            })

            // Always compile kotlin classes before the module descriptor.
            dependsOn(compileKotlinTask)

            // Add the module-info source file.
            // Note that we use the parent dir and an include filter,
            // this is needed for Gradle's module detection to work in
            // org.gradle.api.tasks.compile.JavaCompile.createSpec
            source(sourceFile.parentFile)
            include { it.file == sourceFile }

            // The Kotlin compiler will parse and check module dependencies,
            // but it currently won't compile to a module-info.class file.
            // Note that module checking only works on JDK 9+,
            // because the JDK built-in base modules are not available in earlier versions.
            val javaVersion = compileKotlinTask.kotlinJavaToolchain.javaVersion.getOrNull()
            if (javaVersion?.isJava9Compatible == true) {
                logger.info("Module-info checking is enabled; $compileKotlinTask is compiled using Java $javaVersion")
            } else {
                logger.info("Module-info checking is disabled")
                // Exclude the module-info.java source file from the Kotlin compile task,
                // to prevent compile errors when resolving the module graph.
                compileKotlinTask.exclude { it.file == sourceFile }
            }

            // Set the task outputs and destination directory
            outputs.dir(targetDir)
            destinationDirectory.set(targetDir)

            // Configure JVM compatibility
            sourceCompatibility = JavaVersion.VERSION_1_9.toString()
            targetCompatibility = JavaVersion.VERSION_1_9.toString()

            // Set the Java release version.
            options.release.set(9)

            // Ignore warnings about using 'requires transitive' on automatic modules.
            // not needed when compiling with recent JDKs, e.g. 17
            options.compilerArgs.add("-Xlint:-requires-transitive-automatic")

            // Patch the compileKotlinJvm output classes into the compilation so exporting packages works correctly.
            val kotlinCompileDestinationDir = compileKotlinTask.destinationDirectory.asFile.get()
            options.compilerArgs.addAll(listOf("--patch-module", "$moduleName=$kotlinCompileDestinationDir"))

            // Use the classpath of the compileKotlinJvm task.
            // Also ensure that the module path is used instead of classpath.
            classpath = compileKotlinTask.classpath
            modularity.inferModulePath.set(true)
        }

        tasks.getByName<Jar>(target.artifactsTaskName) {
            manifest {
                attributes("Multi-Release" to true)
            }
            from(compileJavaModuleInfo) {
                into("META-INF/versions/9/")
            }
        }
    }
}
