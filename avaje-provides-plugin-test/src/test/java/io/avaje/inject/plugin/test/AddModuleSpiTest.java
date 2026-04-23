package io.avaje.inject.plugin.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.gradle.testkit.runner.TaskOutcome.SUCCESS;

import java.io.IOException;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AddModuleSpiTest {

  private static final String PLUGIN_DIR = System.getProperty("pluginProjectDir");

  /**
   * A project with a META-INF/services file should end up with a matching
   * {@code provides} directive injected into module-info.class.
   */
  @Test
  void providesDirectiveIsAddedFromServicesFile(@TempDir Path projectDir) throws IOException {
    setupProjectWithService(projectDir,
        "com.example.MyService",
        "com.example.MyServiceImpl");

    var result = runner(projectDir).withArguments("classes", "--stacktrace").build();

    assertThat(result.task(":addModuleSpi").getOutcome()).isEqualTo(SUCCESS);

    var moduleAttr = readModuleAttribute(projectDir);
    assertThat(moduleAttr.provides()).hasSize(1);

    var provides = moduleAttr.provides().getFirst();
    assertThat(internalToFqn(provides.provides().asInternalName()))
        .isEqualTo("com.example.MyService");
    assertThat(provides.providesWith())
        .extracting(e -> internalToFqn(e.asInternalName()))
        .containsExactly("com.example.MyServiceImpl");
  }

  /**
   * A project that has no module-info.java must not fail — the task is silently skipped.
   */
  @Test
  void noModuleInfoIsNoOp(@TempDir Path projectDir) throws IOException {
    setupSettings(projectDir);
    write(projectDir, "build.gradle.kts", """
        plugins {
            java
            id("io.avaje.provides")
        }
        java { toolchain { languageVersion = JavaLanguageVersion.of(24) } }
        """);
    write(projectDir, "src/main/java/com/example/Hello.java", """
        package com.example;
        public class Hello {}
        """);

    var result = runner(projectDir).withArguments("classes", "--stacktrace").build();

    assertThat(result.task(":addModuleSpi").getOutcome()).isEqualTo(SUCCESS);
  }

  /**
   * A {@code provides} directive already present in module-info.java (and therefore baked
   * into module-info.class by javac) should be preserved when there is no matching
   * META-INF/services entry from this project.
   */
  @Test
  void existingProvidesDirectiveIsPreserved(@TempDir Path projectDir) throws IOException {
    setupSettings(projectDir);
    write(projectDir, "build.gradle.kts", """
        plugins {
            java
            id("io.avaje.provides")
        }
        java { toolchain { languageVersion = JavaLanguageVersion.of(24) } }
        """);
    write(projectDir, "src/main/java/module-info.java", """
        module test.module {
            provides com.example.MyService with com.example.MyServiceImpl;
        }
        """);
    write(projectDir, "src/main/java/com/example/MyService.java", """
        package com.example;
        public interface MyService {}
        """);
    write(projectDir, "src/main/java/com/example/MyServiceImpl.java", """
        package com.example;
        public class MyServiceImpl implements MyService {}
        """);
    // No META-INF/services — the processor should keep the directive javac already wrote

    var result = runner(projectDir).withArguments("classes", "--stacktrace").build();

    assertThat(result.task(":addModuleSpi").getOutcome()).isEqualTo(SUCCESS);

    var moduleAttr = readModuleAttribute(projectDir);
    assertThat(moduleAttr.provides()).hasSize(1);
    assertThat(internalToFqn(moduleAttr.provides().getFirst().provides().asInternalName()))
        .isEqualTo("com.example.MyService");
  }

  /**
   * Multiple service implementations listed in the services file must all appear
   * in the {@code provides … with …} directive.
   */
  @Test
  void multipleImplementationsAreAllRegistered(@TempDir Path projectDir) throws IOException {
    setupSettings(projectDir);
    write(projectDir, "build.gradle.kts", """
        plugins {
            java
            id("io.avaje.provides")
        }
        java { toolchain { languageVersion = JavaLanguageVersion.of(24) } }
        """);
    write(projectDir, "src/main/java/module-info.java", "module test.module {}");
    write(projectDir, "src/main/java/com/example/MyService.java",
        "package com.example; public interface MyService {}");
    write(projectDir, "src/main/java/com/example/ImplA.java",
        "package com.example; public class ImplA implements MyService {}");
    write(projectDir, "src/main/java/com/example/ImplB.java",
        "package com.example; public class ImplB implements MyService {}");
    write(projectDir, "src/main/resources/META-INF/services/com.example.MyService",
        "com.example.ImplA\ncom.example.ImplB");

    var result = runner(projectDir).withArguments("classes", "--stacktrace").build();

    assertThat(result.task(":addModuleSpi").getOutcome()).isEqualTo(SUCCESS);

    var moduleAttr = readModuleAttribute(projectDir);
    var provides = moduleAttr.provides().getFirst();
    assertThat(provides.providesWith())
        .extracting(e -> internalToFqn(e.asInternalName()))
        .containsExactlyInAnyOrder("com.example.ImplA", "com.example.ImplB");
  }

  // --- helpers ---

  private void setupProjectWithService(
      Path projectDir, String serviceInterface, String serviceImpl) throws IOException {
    setupSettings(projectDir);
    write(projectDir, "build.gradle.kts", """
        plugins {
            java
            id("io.avaje.provides")
        }
        java { toolchain { languageVersion = JavaLanguageVersion.of(24) } }
        """);
    write(projectDir, "src/main/java/module-info.java", "module test.module {}");

    var ifacePath = "src/main/java/" + serviceInterface.replace('.', '/') + ".java";
    var pkg = serviceInterface.substring(0, serviceInterface.lastIndexOf('.'));
    var ifaceName = serviceInterface.substring(serviceInterface.lastIndexOf('.') + 1);
    write(projectDir, ifacePath, "package %s; public interface %s {}".formatted(pkg, ifaceName));

    var implPath = "src/main/java/" + serviceImpl.replace('.', '/') + ".java";
    var implPkg = serviceImpl.substring(0, serviceImpl.lastIndexOf('.'));
    var implName = serviceImpl.substring(serviceImpl.lastIndexOf('.') + 1);
    write(projectDir, implPath,
        "package %s; public class %s implements %s {}".formatted(implPkg, implName, ifaceName));

    write(projectDir, "src/main/resources/META-INF/services/" + serviceInterface, serviceImpl);
  }

  private void setupSettings(Path projectDir) throws IOException {
    write(projectDir, "settings.gradle.kts", """
        pluginManagement {
            includeBuild("%s")
        }
        rootProject.name = "test"
        """.formatted(PLUGIN_DIR));
  }

  private java.lang.classfile.attribute.ModuleAttribute readModuleAttribute(Path projectDir)
      throws IOException {
    var moduleCF = projectDir.resolve("build/classes/java/main/module-info.class");
    assertThat(moduleCF).exists();
    var classModel = ClassFile.of().parse(moduleCF);
    return classModel.findAttribute(Attributes.module()).orElseThrow(
        () -> new AssertionError("module-info.class has no ModuleAttribute"));
  }

  private GradleRunner runner(Path projectDir) {
    return GradleRunner.create()
        .withProjectDir(projectDir.toFile())
        .forwardOutput();
  }

  private static void write(Path base, String relative, String content) throws IOException {
    var target = base.resolve(relative);
    Files.createDirectories(target.getParent());
    Files.writeString(target, content);
  }

  private static String internalToFqn(String internalName) {
    return internalName.replace('/', '.');
  }
}
