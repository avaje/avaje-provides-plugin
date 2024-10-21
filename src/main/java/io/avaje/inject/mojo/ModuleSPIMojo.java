package io.avaje.inject.mojo;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

/** Plugin that transforms the project module-info class file to register META-INF/services. */
@Mojo(name = "add-module-spi", defaultPhase = LifecyclePhase.PROCESS_CLASSES)
public class ModuleSPIMojo extends AbstractMojo {

  @Parameter(defaultValue = "${project}", readonly = true, required = true)
  private MavenProject project;

  @Override
  public void execute() throws MojoExecutionException {

    var canRun =
        Integer.getInteger("java.specification.version") == 23
            && ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .anyMatch("--enable-preview"::equals);

    if (!canRun) {
      getLog()
          .warn(
              "This version of the avaje-provides-plugin only works on JDK 23 with --enable-preview cofigured in MAVEN_OPTS");
      return;
    }

    new ModuleSPIProcessor(project, getLog(), compiledClasses()).execute();
  }

  private Set<String> compiledClasses() throws MojoExecutionException {
    try {

      final Set<String> targetClasses = new HashSet<>();

      var classpathElements = project.getCompileClasspathElements();
      for (var element : classpathElements) {

        try (var paths = Files.walk(new File(element).toPath())) {
          paths
              .filter(p -> p.toString().contains(".class"))
              .map(p -> p.getFileName().toString().replace(".class", ""))
              .forEach(targetClasses::add);
        }
      }
      return targetClasses;
    } catch (final Exception e) {
      throw new MojoExecutionException("Failed to get compiled classes", e);
    }
  }
}
