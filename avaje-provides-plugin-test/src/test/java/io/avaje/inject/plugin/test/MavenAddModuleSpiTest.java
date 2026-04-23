package io.avaje.inject.plugin.test;

import static io.avaje.inject.plugin.test.MavenDisableAptValidationTest.invoke;
import static io.avaje.inject.plugin.test.MavenDisableAptValidationTest.write;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MavenAddModuleSpiTest {

  private static final String PLUGIN_VERSION = System.getProperty("mavenPluginVersion", "2.3");

  @BeforeAll
  static void requiresMaven() {
    assumeTrue(mavenAvailable(), "Skipping Maven tests: 'mvn' not found on PATH");
  }

  /**
   * A project with a META-INF/services file should end up with the matching
   * {@code provides} directive injected into module-info.class.
   */
  @Test
  void providesDirectiveIsAddedFromServicesFile(@TempDir Path projectDir) throws Exception {
    setupProjectWithService(projectDir, "com.example.MyService", "com.example.MyServiceImpl");

    invoke(projectDir, "process-classes");

    var moduleAttr = readModuleAttribute(projectDir);
    assertThat(moduleAttr.provides()).hasSize(1);

    var provides = moduleAttr.provides().getFirst();
    assertThat(internalToFqn(provides.provides().asInternalName())).isEqualTo("com.example.MyService");
    assertThat(provides.providesWith())
        .extracting(e -> internalToFqn(e.asInternalName()))
        .containsExactly("com.example.MyServiceImpl");
  }

  /**
   * A project with no module-info.java must not fail — goal is silently skipped.
   */
  @Test
  void noModuleInfoIsNoOp(@TempDir Path projectDir) throws Exception {
    writePom(projectDir, "test-no-module");
    write(projectDir, "src/main/java/com/example/Hello.java",
        "package com.example; public class Hello {}");

    invoke(projectDir, "process-classes");

    assertThat(projectDir.resolve("target/classes/module-info.class")).doesNotExist();
  }

  /**
   * A {@code provides} directive already baked into module-info.class by javac
   * must survive when there is no matching META-INF/services entry from this project.
   */
  @Test
  void existingProvidesDirectiveIsPreserved(@TempDir Path projectDir) throws Exception {
    writePom(projectDir, "test-preserve-provides");
    write(projectDir, "src/main/java/module-info.java", """
        module test.module {
            provides com.example.MyService with com.example.MyServiceImpl;
        }
        """);
    write(projectDir, "src/main/java/com/example/MyService.java",
        "package com.example; public interface MyService {}");
    write(projectDir, "src/main/java/com/example/MyServiceImpl.java",
        "package com.example; public class MyServiceImpl implements MyService {}");
    // No META-INF/services — the javac-written provides directive should be preserved

    invoke(projectDir, "process-classes");

    var moduleAttr = readModuleAttribute(projectDir);
    assertThat(moduleAttr.provides()).hasSize(1);
    assertThat(internalToFqn(moduleAttr.provides().getFirst().provides().asInternalName()))
        .isEqualTo("com.example.MyService");
  }

  /**
   * Multiple implementations in the services file must all appear in the provides directive.
   */
  @Test
  void multipleImplementationsAreAllRegistered(@TempDir Path projectDir) throws Exception {
    writePom(projectDir, "test-multi-impl");
    write(projectDir, "src/main/java/module-info.java", "module test.module {}");
    write(projectDir, "src/main/java/com/example/MyService.java",
        "package com.example; public interface MyService {}");
    write(projectDir, "src/main/java/com/example/ImplA.java",
        "package com.example; public class ImplA implements MyService {}");
    write(projectDir, "src/main/java/com/example/ImplB.java",
        "package com.example; public class ImplB implements MyService {}");
    write(projectDir, "src/main/resources/META-INF/services/com.example.MyService",
        "com.example.ImplA\ncom.example.ImplB");

    invoke(projectDir, "process-classes");

    var provides = readModuleAttribute(projectDir).provides().getFirst();
    assertThat(provides.providesWith())
        .extracting(e -> internalToFqn(e.asInternalName()))
        .containsExactlyInAnyOrder("com.example.ImplA", "com.example.ImplB");
  }

  // --- helpers ---

  private void setupProjectWithService(
      Path projectDir, String serviceInterface, String serviceImpl) throws IOException {
    writePom(projectDir, "test-add-spi");
    write(projectDir, "src/main/java/module-info.java", "module test.module {}");

    var pkg = serviceInterface.substring(0, serviceInterface.lastIndexOf('.'));
    var ifaceName = serviceInterface.substring(serviceInterface.lastIndexOf('.') + 1);
    write(projectDir, "src/main/java/" + serviceInterface.replace('.', '/') + ".java",
        "package %s; public interface %s {}".formatted(pkg, ifaceName));

    var implPkg = serviceImpl.substring(0, serviceImpl.lastIndexOf('.'));
    var implName = serviceImpl.substring(serviceImpl.lastIndexOf('.') + 1);
    write(projectDir, "src/main/java/" + serviceImpl.replace('.', '/') + ".java",
        "package %s; public class %s implements %s {}".formatted(implPkg, implName, ifaceName));

    write(projectDir, "src/main/resources/META-INF/services/" + serviceInterface, serviceImpl);
  }

  private void writePom(Path projectDir, String artifactId) throws IOException {
    write(projectDir, "pom.xml", """
        <project xmlns="http://maven.apache.org/POM/4.0.0"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/maven-v4_0_0.xsd">
          <modelVersion>4.0.0</modelVersion>
          <groupId>io.avaje.test</groupId>
          <artifactId>%s</artifactId>
          <version>1.0</version>
          <properties>
            <maven.compiler.release>24</maven.compiler.release>
          </properties>
          <build>
            <plugins>
              <plugin>
                <groupId>io.avaje</groupId>
                <artifactId>avaje-provides-maven-plugin</artifactId>
                <version>%s</version>
                <executions>
                  <execution>
                    <id>disable-apt</id>
                    <goals><goal>disable-apt-validation</goal></goals>
                  </execution>
                  <execution>
                    <id>add-spi</id>
                    <goals><goal>add-module-spi</goal></goals>
                  </execution>
                </executions>
              </plugin>
            </plugins>
          </build>
        </project>
        """.formatted(artifactId, PLUGIN_VERSION));
  }

  private java.lang.classfile.attribute.ModuleAttribute readModuleAttribute(Path projectDir)
      throws IOException {
    var moduleCF = projectDir.resolve("target/classes/module-info.class");
    assertThat(moduleCF).exists();
    return ClassFile.of().parse(moduleCF)
        .findAttribute(Attributes.module())
        .orElseThrow(() -> new AssertionError("module-info.class has no ModuleAttribute"));
  }

  private static String internalToFqn(String internalName) {
    return internalName.replace('/', '.');
  }

  private static boolean mavenAvailable() {
    try {
      return new ProcessBuilder("mvn", "--version")
          .redirectErrorStream(true)
          .start()
          .waitFor() == 0;
    } catch (Exception e) {
      return false;
    }
  }
}
