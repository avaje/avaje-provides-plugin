package io.avaje.inject.gradle;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.tasks.TaskAction;

/** Task that disables avaje's APT module validation by writing a flag file. */
public abstract class DisableAptValidationTask extends DefaultTask {

  private static final String DISABLING_AVAJE_MODULE_VERIFICATION = "disabling avaje module verification";

  @TaskAction
  public void execute() {
    var buildDir = getProject().getLayout().getBuildDirectory().getAsFile().get();
    if (!buildDir.exists()) {
      buildDir.mkdirs();
    }

    try (var writer = new FileWriter(new File(buildDir, "avaje-plugin-exists.txt"))) {
      writer.write(DISABLING_AVAJE_MODULE_VERIFICATION);
      getLogger().info(DISABLING_AVAJE_MODULE_VERIFICATION);
    } catch (final IOException e) {
      throw new GradleException("Failed to write avaje-plugin-exists.txt", e);
    }
  }
}
