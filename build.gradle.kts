/*
 * gpu-vulkan — RuneLite external plugin
 *
 * Standalone Gradle build for the Vulkan-backed renderer plugin. Mirrors the
 * shape of the runelite/example-plugin template: depends on a published
 * net.runelite:client release for the public API, and additionally pulls in
 * lwjgl-vulkan + lwjgl-jawt which the stock client does not ship.
 *
 * Shaders compile at build time via glslangValidator (must be on PATH or via
 * the GLSLANG env var). The .spv outputs land on the classpath at
 * /net/runelite/client/plugins/gpuvulkan/ so ScenePipeline/UiPipeline can load
 * them with getResourceAsStream.
 */

plugins {
	java
}

repositories {
	mavenLocal()
	maven {
		url = uri("https://repo.runelite.net")
		content { includeGroupAndSubgroups("net.runelite") }
	}
	mavenCentral()
}

group = "net.runelite.client.plugins.gpuvulkan"
version = "1.0.0-SNAPSHOT"

java {
	sourceCompatibility = JavaVersion.VERSION_11
	targetCompatibility = JavaVersion.VERSION_11
}

val runeLiteVersion = "latest.release"
// lwjgl-core tracks RuneLite's libs.versions.toml (currently 3.3.2) so the
// per-platform native classifier matches what the host client loads.
val lwjglVersion = "3.3.2"
// lwjgl-vulkan AND lwjgl-jawt are intentionally newer (3.3.6) — vulkan needs
// the Vulkan Video Encode H.264/H.265 KHR bindings for the encoder zero-copy
// path, and lwjgl-jawt has to track lwjgl-vulkan's version (NOT lwjgl-core's)
// or JAWTSurfaceLayers / JAWT native lookup fails at runtime.
val lwjglVulkanVersion = "3.3.6"
val lombokVersion = "1.18.34"

dependencies {
	// RuneLite public API — provided by the host client at runtime.
	compileOnly("net.runelite:client:$runeLiteVersion")
	compileOnly("net.runelite:rlawt:1.8")

	// LWJGL Vulkan + JAWT — NOT shipped by stock RuneLite. The plugin jar
	// must carry these (or the user must side-load them). lwjgl-vulkan has
	// no native; the Vulkan loader resolves the platform ICD at runtime.
	implementation("org.lwjgl:lwjgl:$lwjglVersion")
	implementation("org.lwjgl:lwjgl-vulkan:$lwjglVulkanVersion")
	implementation("org.lwjgl:lwjgl-jawt:$lwjglVulkanVersion")

	compileOnly("org.projectlombok:lombok:$lombokVersion")
	annotationProcessor("org.projectlombok:lombok:$lombokVersion")

	// Test path: full client + jshell so IDE-run via GpuVulkanPluginTest
	// boots a real RuneLite with the plugin loaded.
	testImplementation("net.runelite:client:$runeLiteVersion")
	testImplementation("net.runelite:jshell:$runeLiteVersion")
	testImplementation("junit:junit:4.13.2")

	// LWJGL natives for development on each host platform. lwjgl-vulkan
	// itself has no native; only lwjgl-core needs per-OS natives. Add the
	// classifier matching whatever machine you're running tests from.
	val osName = System.getProperty("os.name").lowercase()
	val osArch = System.getProperty("os.arch").lowercase()
	val nativesClassifier = when {
		osName.contains("mac") || osName.contains("darwin") ->
			if (osArch.contains("aarch64") || osArch.contains("arm")) "natives-macos-arm64"
			else "natives-macos"
		osName.contains("win") -> "natives-windows"
		osArch.contains("aarch64") || osArch.contains("arm") -> "natives-linux-arm64"
		else -> "natives-linux"
	}
	testRuntimeOnly("org.lwjgl:lwjgl:$lwjglVersion:$nativesClassifier")
}

// glsl → SPIR-V compile. Ported verbatim from runelite-vkport's
// runelite-client/build.gradle.kts so behavior matches the source tree.
val gpuVulkanShaderSrc = file("src/main/shaders/gpuvulkan")
val gpuVulkanShaderOut = layout.buildDirectory
	.dir("generated/resources/net/runelite/client/plugins/gpuvulkan")
	.get().asFile
val glslangValidator = providers.environmentVariable("GLSLANG").getOrElse("glslangValidator")

val compileGpuVulkanShaders = tasks.register("compileGpuVulkanShaders") {
	group = "build"
	description = "Compile gpuvulkan GLSL shaders to SPIR-V"

	inputs.dir(gpuVulkanShaderSrc)
	outputs.dir(gpuVulkanShaderOut)

	doLast {
		gpuVulkanShaderOut.mkdirs()
		fileTree(gpuVulkanShaderSrc).matching {
			include("**/*.vert", "**/*.frag", "**/*.comp", "**/*.geom")
		}.forEach { src ->
			val outFile = File(gpuVulkanShaderOut, "${src.name}.spv")
			val result = providers.exec {
				commandLine(glslangValidator, "-V", src.absolutePath, "-o", outFile.absolutePath)
			}.result.get()
			if (result.exitValue != 0) {
				throw GradleException("glslangValidator failed for $src")
			}
		}
	}
}

sourceSets {
	main {
		resources {
			srcDir(layout.buildDirectory.dir("generated/resources"))
		}
	}
}

tasks.named("processResources") {
	dependsOn(compileGpuVulkanShaders)
}

tasks.withType<JavaCompile> {
	options.encoding = "UTF-8"
}

tasks.withType<Test> {
	useJUnit()
}
