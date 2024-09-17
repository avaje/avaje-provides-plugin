package io.avaje.inject.mojo;

import java.lang.management.ManagementFactory;

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

    new ModuleSPIProcessor(project, getLog()).execute();
  }
}
