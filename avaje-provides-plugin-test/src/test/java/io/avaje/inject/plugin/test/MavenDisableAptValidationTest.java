package io.avaje.inject.plugin.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.maven.shared.invoker.DefaultInvocationRequest;
import org.apache.maven.shared.invoker.DefaultInvoker;
import org.apache.maven.shared.invoker.MavenInvocationException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MavenDisableAptValidationTest {

  private static final String PLUGIN_VERSION = System.getProperty("mavenPluginVersion", "2.3");

  @BeforeAll
  static void requiresMaven() {
    assumeTrue(mavenAvailable(), "Skipping Maven tests: 'mvn' not found on PATH");
  }

  @Test
  void flagFileIsWrittenToBuildDir(@TempDir Path projectDir) throws Exception {
    writePom(projectDir);

    invoke(projectDir, "process-resources");

    assertThat(projectDir.resolve("target/avaje-plugin-exists.txt"))
        .exists()
        .hasContent("disabling avaje module verification");
  }

  @Test
  void goalIsIdempotentOnSecondRun(@TempDir Path projectDir) throws Exception {
    writePom(projectDir);

    invoke(projectDir, "process-resources");
    invoke(projectDir, "process-resources");

    assertThat(projectDir.resolve("target/avaje-plugin-exists.txt")).exists();
  }

  // --- helpers ---

  private static void writePom(Path projectDir) throws IOException {
    write(projectDir, "pom.xml", """
        <project xmlns="http://maven.apache.org/POM/4.0.0"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/maven-v4_0_0.xsd">
          <modelVersion>4.0.0</modelVersion>
          <groupId>io.avaje.test</groupId>
          <artifactId>test-disable-apt</artifactId>
          <version>1.0</version>
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
                </executions>
              </plugin>
            </plugins>
          </build>
        </project>
        """.formatted(PLUGIN_VERSION));
  }

  static void invoke(Path projectDir, String... goals) throws MavenInvocationException {
    var request = new DefaultInvocationRequest();
    request.setPomFile(projectDir.resolve("pom.xml").toFile());
    request.setGoals(List.of(goals));
    request.setBatchMode(true);

    var result = new DefaultInvoker().execute(request);
    assertThat(result.getExitCode())
        .as("Maven build failed (exit %d)", result.getExitCode())
        .isZero();
  }

  static void write(Path base, String relative, String content) throws IOException {
    var target = base.resolve(relative);
    Files.createDirectories(target.getParent());
    Files.writeString(target, content);
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
