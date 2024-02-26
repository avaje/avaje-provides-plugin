package io.avaje.inject.mojo;

import static java.util.function.Predicate.not;
import static java.util.stream.Collectors.joining;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.classfile.ClassElement;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.attribute.ModuleAttribute;
import java.lang.classfile.attribute.ModuleProvideInfo;
import java.lang.classfile.attribute.ModuleRequireInfo;
import java.lang.classfile.constantpool.Utf8Entry;
import java.lang.classfile.attribute.ModuleAttribute.ModuleAttributeBuilder;
import java.lang.constant.ClassDesc;
import java.lang.constant.ModuleDesc;
import java.lang.reflect.AccessFlag;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

/**
 * Plugin that generates <code>target/avaje-module-provides.txt</code> and <code>
 * target/avaje-plugin-provides.txt</code> based on the avaje-inject modules and plugins in the
 * classpath.
 *
 * <p>This allows the avaje-inject-generator annotation processor to be aware of all the components
 * and plugins provided by other modules in the classpath at compile time.
 */
@Mojo(
    name = "module-spi",
    defaultPhase = LifecyclePhase.PROCESS_CLASSES,
    requiresDependencyResolution = ResolutionScope.COMPILE)
public class ModuleSPIMojo extends AbstractMojo {

  private static final String IO_AVAJE_JSONB_PLUGIN = "io.avaje.jsonb.plugin";
  private static final String IO_AVAJE_INJECT = "io.avaje.inject";
  private static final String IO_AVAJE_VALIDATOR_PLUGIN = "io.avaje.validation.plugin";
  private static final String IO_AVAJE_VALIDATOR_HTTP_PLUGIN = "io.avaje.validation.http";

  @Parameter(defaultValue = "${project}", readonly = true, required = true)
  private MavenProject project;

  private static final Set<String> avajeModuleNames = new HashSet<>();

  @Override
  public void execute() throws MojoExecutionException {

    final var directory = new File(project.getBuild().getDirectory());
    if (!directory.exists()) {
      throw new MojoExecutionException("Failed to find build folder");
    }

    var dirPath = directory.getAbsolutePath();

    var moduleCF = Paths.get(STR."\{dirPath}\\classes\\module-info.class");
    var servicesDirectory = Paths.get(STR."\{dirPath}\\classes\\META-INF\\services");

    if (!moduleCF.toFile().exists()) {
      // no module-info to modify
      return;
    }

    try {
      var newModuleFile = transform(moduleCF, servicesDirectory);
      Files.delete(moduleCF);
      Files.write(moduleCF, newModuleFile, StandardOpenOption.CREATE_NEW);
    } catch (final IOException e) {
      throw new MojoExecutionException("Failed to write spi classes", e);
    }
  }

  private byte[] transform(final Path moduleCF, Path path) throws IOException {
    ClassFile cf = ClassFile.of();
    ClassModel classModel = cf.parse(moduleCF);
    return cf.build(
        classModel.thisClass().asSymbol(),
        classBuilder -> {
          for (ClassElement ce : classModel) {
            if (!(ce instanceof ModuleAttribute mm)) {

              classBuilder.with(ce);

            } else {

              var newModule =
                  ModuleAttribute.of(
                      mm.moduleName().asSymbol(), b -> transformDirectives(mm, b, path));

              classBuilder.with(newModule);
            }
          }
        });
  }

  private void transformDirectives(
      ModuleAttribute mm, ModuleAttributeBuilder b, Path metaInfServicesPath) {

    mm.moduleFlags().forEach(b::moduleFlags);
    b.moduleFlags(mm.moduleFlagsMask());
    mm.exports().forEach(b::exports);
    mm.opens().forEach(b::opens);

    mm.requires().stream()
        .filter(r -> !r.has(AccessFlag.STATIC))
        .map(r -> r.requires().name().toString())
        .filter(n -> n.contains("io.avaje"))
        .forEach(avajeModuleNames::add);

    mm.requires().forEach(r -> requires(r, b));

    mm.uses().forEach(b::uses);

    if (!metaInfServicesPath.toFile().exists()) {
      mm.provides().stream().forEach(b::provides);
      return;
    }

    try (var servicesDir = Files.walk(metaInfServicesPath)) {
      addServices(mm, b, servicesDir);

    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  /** Will add the avaje plugins if applicable */
  private void requires(ModuleRequireInfo r, ModuleAttributeBuilder b) {

    final var moduleString = r.requires().name().stringValue();

    if (r.has(AccessFlag.STATIC) || !moduleString.contains("avaje")) {

      b.requires(r);
      return;
    }

    var log = getLog();
    b.requires(r);
    switch (moduleString) {
      case "io.avaje.jsonb" -> {
        if (!avajeModuleNames.contains(IO_AVAJE_JSONB_PLUGIN)
            && avajeModuleNames.contains(IO_AVAJE_INJECT)) {

          var plugin =
              ModuleRequireInfo.of(
                  ModuleDesc.of(IO_AVAJE_JSONB_PLUGIN),
                  r.requiresFlagsMask(),
                  r.requiresVersion().map(Utf8Entry::stringValue).orElse(null));
          b.requires(plugin);
          log.info(STR."Adding `requires \{IO_AVAJE_JSONB_PLUGIN};` to compiled module-info.class");
         }
      }

      case "io.avaje.validation" -> {
        if (avajeModuleNames.contains(IO_AVAJE_INJECT)) {
          var hasHttp = avajeModuleNames.contains("io.avaje.http.api");

          if (!avajeModuleNames.contains(IO_AVAJE_VALIDATOR_PLUGIN)
              && !avajeModuleNames.contains(IO_AVAJE_VALIDATOR_HTTP_PLUGIN)) {

            var pluginModule = hasHttp ? IO_AVAJE_VALIDATOR_HTTP_PLUGIN : IO_AVAJE_VALIDATOR_PLUGIN;
            var plugin =
                ModuleRequireInfo.of(
                    ModuleDesc.of(pluginModule),
                    r.requiresFlagsMask(),
                    r.requiresVersion().map(Utf8Entry::stringValue).orElse(null));
            b.requires(plugin);
            log.info(STR."Adding `requires \{pluginModule};` to compiled module-info.class");
          } else if (!avajeModuleNames.contains(IO_AVAJE_VALIDATOR_HTTP_PLUGIN) && hasHttp) {
            var plugin =
                ModuleRequireInfo.of(
                    ModuleDesc.of(IO_AVAJE_VALIDATOR_HTTP_PLUGIN),
                    r.requiresFlagsMask(),
                    r.requiresVersion().map(Utf8Entry::stringValue).orElse(null));
            b.requires(plugin);
            log.info(STR."Adding `requires \{IO_AVAJE_VALIDATOR_HTTP_PLUGIN};` to compiled module-info.class");
          }
        }
      }
      default -> {
        // nothing to do
      }
    }
  }

  private void addServices(ModuleAttribute mm, ModuleAttributeBuilder b, Stream<Path> servicesDir) {
    var serviceMap =
        servicesDir
            .skip(1)
            .sorted(Comparator.comparing(Path::getFileName))
            .collect(
                Collectors.toMap(
                    p -> p.getFileName().toString(),
                    p -> {
                      try {
                        return Files.readAllLines(p).stream()
                            .map(s -> s.replace("\s", "").replace("$", ".").split(","))
                            .flatMap(Arrays::stream)
                            .filter(not(String::isBlank))
                            .map(ClassDesc::of)
                            .toList();
                      } catch (IOException e) {
                        throw new UncheckedIOException(e);
                      }
                    }));

    mm.provides().stream()
        .filter(p -> !serviceMap.containsKey(p.provides().name().stringValue().replace("/", ".")))
        .forEach(b::provides);
    var log = getLog();
    serviceMap.forEach(
        (k, v) -> {
          var provides = ClassDesc.of(k);
          var with = v.stream().map(ClassDesc::displayName).collect(joining(","));
          log.info(STR."Adding `provides \{provides.displayName()} with \{with};` to compiled module-info.class");

          b.provides(ModuleProvideInfo.of(provides, v));
        });
  }
}
