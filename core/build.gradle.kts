plugins {
    id("java")

    alias(libs.plugins.paperweight.userdev)
}

group = "live.minehub"

repositories {
    mavenCentral()
}

dependencies {
    paperweight.paperDevBundle("${libs.versions.minecraft.get()}.build.+")
    compileOnly(libs.zstd)
    testImplementation(libs.zstd)
    testRuntimeOnly("dev.hallock.zstd:platforms:${libs.versions.zstd.get()}")
    testImplementation(platform("org.junit:junit-bom:6.0.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
