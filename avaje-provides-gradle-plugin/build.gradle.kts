plugins {
    `java-gradle-plugin`
    `maven-publish`
}

group = "io.avaje"
version = "2.3"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(24)
    }
}

gradlePlugin {
    plugins {
        create("avajeProvides") {
            id = "io.avaje.provides"
            implementationClass = "io.avaje.inject.gradle.AvajeProvidePlugin"
            displayName = "Avaje Provides Plugin"
            description = "Transforms module-info.class to register META-INF/services and disables APT module validation"
        }
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "io.avaje"
            artifactId = "avaje-provides-gradle-plugin"
            from(components["java"])
        }
    }
}
