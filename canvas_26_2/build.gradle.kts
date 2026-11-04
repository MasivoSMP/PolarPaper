plugins {
    id("java")

    alias(libs.plugins.weaver.userdev)
}

group = "live.minehub"

// Compile-only mirror of the optional patched-server marker; never bundled into Polar.
val compileEphemeralContract = tasks.register<JavaCompile>("compileEphemeralContract") {
    source(fileTree("src/ephemeralApi/java"))
    classpath = files()
    destinationDirectory.set(layout.buildDirectory.dir("classes/ephemeralApi"))
    options.release.set(25)
}

repositories {
    mavenCentral()
    maven("https://maven.canvasmc.io/releases")
}

dependencies {
    paperweight.canvasDevBundle("26.2.build.+")
    compileOnly(project(":core"))
    compileOnly(files(compileEphemeralContract.flatMap { it.destinationDirectory }))
}
