package io.avaje.inject.gradle;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;

import org.gradle.api.DefaultTask;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskAction;

/** Task that transforms the project module-info class file to register META-INF/services. */
public abstract class AddModuleSpiTask extends DefaultTask {

  @TaskAction
  public void execute() {
    var buildDir = getProject().getLayout().getBuildDirectory().getAsFile().get().toPath();
    new ModuleSPIProcessor(buildDir, getLogger(), compiledClasses()).execute();
  }

  private Set<String> compiledClasses() {
    try {
      var sourceSets = (SourceSetContainer) getProject().getExtensions().getByName("sourceSets");
      var classesDirs = sourceSets.getByName("main").getOutput().getClassesDirs();

      Set<String> classes = new HashSet<>();
      for (File dir : classesDirs) {
        if (!dir.exists()) continue;
        try (var paths = Files.walk(dir.toPath())) {
          paths
              .filter(p -> p.toString().endsWith(".class"))
              .map(p -> p.getFileName().toString().replace(".class", ""))
              .forEach(classes::add);
        }
      }
      return classes;
    } catch (final Exception e) {
      getLogger().warn("Failed to get compiled classes", e);
      return Set.of();
    }
  }
}
