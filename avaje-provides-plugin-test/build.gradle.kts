plugins {
    java
}

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(24)
    }
}

dependencies {
    testImplementation(gradleTestKit())
    testImplementation("org.apache.maven.shared:maven-invoker:3.3.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testImplementation("org.assertj:assertj-core:3.27.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    // Gradle plugin: ensure it's compiled before tests run, then expose its source dir
    // so GradleRunner test projects can reference it via includeBuild("$pluginProjectDir")
    dependsOn(gradle.includedBuild("avaje-provides-gradle-plugin").task(":classes"))
    systemProperty("pluginProjectDir", file("../avaje-provides-gradle-plugin").absolutePath)
    // Maven plugin: version used to write test pom.xml files; the artifact must already be
    // installed in the local Maven repo (CI runs `mvn install` on the Maven module first)
    systemProperty("mavenPluginVersion", "2.3")
}
