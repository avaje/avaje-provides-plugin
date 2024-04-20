package io.avaje.inject.mojo;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

/** Mojo that disables avaje's apt module validation */
@Mojo(
    name = "disable-apt-validation",
    defaultPhase = LifecyclePhase.PROCESS_RESOURCES,
    requiresDependencyResolution = ResolutionScope.COMPILE)
public class DisableModuleValidationMojo extends AbstractMojo {

  private static final String DISABLING_AVAJE_MODULE_VERIFICATION =
      "disabling avaje module verification";

  @Parameter(defaultValue = "${project}", readonly = true, required = true)
  private MavenProject project;

  @Override
  public void execute() throws MojoExecutionException {
    final var directory = new File(project.getBuild().getDirectory());
    if (!directory.exists()) {
      directory.mkdirs();
    }

    try (var flagFile = createFileWriter("avaje-plugin-exists.txt"); ) {

      flagFile.append(DISABLING_AVAJE_MODULE_VERIFICATION);
      getLog().info(DISABLING_AVAJE_MODULE_VERIFICATION);

    } catch (final IOException e) {
      throw new MojoExecutionException("Failed to write spi classes", e);
    }
  }

  private FileWriter createFileWriter(String string) throws IOException {
    return new FileWriter(new File(project.getBuild().getDirectory(), string));
  }
}
