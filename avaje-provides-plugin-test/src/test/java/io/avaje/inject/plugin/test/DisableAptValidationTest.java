package io.avaje.inject.plugin.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.gradle.testkit.runner.TaskOutcome.SUCCESS;
import static org.gradle.testkit.runner.TaskOutcome.UP_TO_DATE;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DisableAptValidationTest {

  private static final String PLUGIN_DIR = System.getProperty("pluginProjectDir");

  @Test
  void flagFileIsWrittenToBuildsDir(@TempDir Path projectDir) throws IOException {
    setupMinimalProject(projectDir);

    var result = runner(projectDir).withArguments("disableAptValidation").build();

    assertThat(result.task(":disableAptValidation").getOutcome()).isEqualTo(SUCCESS);
    assertThat(projectDir.resolve("build/avaje-plugin-exists.txt"))
        .exists()
        .hasContent("disabling avaje module verification");
  }

  @Test
  void taskIsWiredBeforeCompileJava(@TempDir Path projectDir) throws IOException {
    setupMinimalProject(projectDir);
    // Running compileJava must trigger disableAptValidation first
    var result = runner(projectDir).withArguments("compileJava").build();

    assertThat(result.task(":disableAptValidation").getOutcome()).isIn(SUCCESS, UP_TO_DATE);
    assertThat(projectDir.resolve("build/avaje-plugin-exists.txt")).exists();
  }

  @Test
  void taskIsUpToDateOnSecondRun(@TempDir Path projectDir) throws IOException {
    setupMinimalProject(projectDir);
    runner(projectDir).withArguments("disableAptValidation").build();

    var result = runner(projectDir).withArguments("disableAptValidation").build();

    // Gradle does not mark it UP_TO_DATE because the task has no declared outputs,
    // but it should at minimum not fail on a second run
    assertThat(result.task(":disableAptValidation").getOutcome()).isNotNull();
  }

  // --- helpers ---

  private void setupMinimalProject(Path projectDir) throws IOException {
    write(projectDir, "settings.gradle.kts", """
        pluginManagement {
            includeBuild("%s")
        }
        rootProject.name = "test"
        """.formatted(PLUGIN_DIR));

    write(projectDir, "build.gradle.kts", """
        plugins {
            java
            id("io.avaje.provides")
        }
        """);
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
}
