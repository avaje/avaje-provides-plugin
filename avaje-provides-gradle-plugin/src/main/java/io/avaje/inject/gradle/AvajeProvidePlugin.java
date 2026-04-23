package io.avaje.inject.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;

/** Gradle plugin equivalent of the avaje-provides-maven-plugin. */
public class AvajeProvidePlugin implements Plugin<Project> {

  @Override
  public void apply(Project project) {
    var disableAptValidation =
        project.getTasks().register("disableAptValidation", DisableAptValidationTask.class, task -> {
          task.setGroup("avaje");
          task.setDescription("Writes a flag file so avaje APT skips module validation");
        });

    var addModuleSpi =
        project.getTasks().register("addModuleSpi", AddModuleSpiTask.class, task -> {
          task.setGroup("avaje");
          task.setDescription("Transforms module-info.class to register META-INF/services providers");
          task.dependsOn("compileJava");
        });

    // Mirror Maven lifecycle: disableAptValidation runs before compileJava,
    // addModuleSpi runs after compileJava (as part of classes).
    project.getTasks().named("compileJava", task -> task.dependsOn(disableAptValidation));
    project.getTasks().named("classes", task -> task.dependsOn(addModuleSpi));
  }
}
