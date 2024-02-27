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

/** Plugin that transforms the project module-info class file to register META-INF/services. */
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

  private byte[] transform(final Path moduleCF, Path metaInfServicesPath) throws IOException {
    ClassFile cf = ClassFile.of();
    ClassModel classModel = cf.parse(moduleCF);
    return cf.build(
        classModel.thisClass().asSymbol(),
        classBuilder -> {
          for (ClassElement ce : classModel) {
            if (!(ce instanceof ModuleAttribute ma)) {

              classBuilder.with(ce);

            } else {

              var newModule =
                  ModuleAttribute.of(
                      ma.moduleName().asSymbol(), b -> transformDirectives(ma, b, metaInfServicesPath));

              classBuilder.with(newModule);
            }
          }
        });
  }

  private void transformDirectives(
      ModuleAttribute moduleAttribute, ModuleAttributeBuilder moduleBuilder, Path metaInfServicesPath) {

    moduleAttribute.moduleFlags().forEach(moduleBuilder::moduleFlags);
    moduleBuilder.moduleFlags(moduleAttribute.moduleFlagsMask());
    moduleAttribute.exports().forEach(moduleBuilder::exports);
    moduleAttribute.opens().forEach(moduleBuilder::opens);

    moduleAttribute.requires().stream()
        .filter(r -> !r.has(AccessFlag.STATIC))
        .map(r -> r.requires().name().toString())
        .filter(n -> n.contains("io.avaje"))
        .forEach(avajeModuleNames::add);

    moduleAttribute.requires().forEach(r -> requires(r, moduleBuilder));

    moduleAttribute.uses().forEach(moduleBuilder::uses);

    if (!metaInfServicesPath.toFile().exists()) {
      moduleAttribute.provides().stream().forEach(moduleBuilder::provides);
      return;
    }

    try (var servicesDir = Files.walk(metaInfServicesPath)) {
      addServices(moduleAttribute, moduleBuilder, servicesDir);

    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  /** Will add the avaje-inject plugins if applicable so JPMS applications work correctly */
  private void requires(ModuleRequireInfo moduleRequires, ModuleAttributeBuilder moduleBuilder) {

    final var moduleString = moduleRequires.requires().name().stringValue();

    if (moduleRequires.has(AccessFlag.STATIC) || !moduleString.contains("avaje")) {

      moduleBuilder.requires(moduleRequires);
      return;
    }

    var log = getLog();
    moduleBuilder.requires(moduleRequires);
    switch (moduleString) {
      case "io.avaje.jsonb" -> {
        if (!avajeModuleNames.contains(IO_AVAJE_JSONB_PLUGIN)
            && avajeModuleNames.contains(IO_AVAJE_INJECT)) {

          var plugin =
              ModuleRequireInfo.of(
                  ModuleDesc.of(IO_AVAJE_JSONB_PLUGIN),
                  moduleRequires.requiresFlagsMask(),
                  moduleRequires.requiresVersion().map(Utf8Entry::stringValue).orElse(null));
          moduleBuilder.requires(plugin);
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
                    moduleRequires.requiresFlagsMask(),
                    moduleRequires.requiresVersion().map(Utf8Entry::stringValue).orElse(null));
            moduleBuilder.requires(plugin);
            log.info(STR."Adding `requires \{pluginModule};` to compiled module-info.class");
          } else if (!avajeModuleNames.contains(IO_AVAJE_VALIDATOR_HTTP_PLUGIN) && hasHttp) {
            var plugin =
                ModuleRequireInfo.of(
                    ModuleDesc.of(IO_AVAJE_VALIDATOR_HTTP_PLUGIN),
                    moduleRequires.requiresFlagsMask(),
                    moduleRequires.requiresVersion().map(Utf8Entry::stringValue).orElse(null));
            moduleBuilder.requires(plugin);
            log.info(STR."Adding `requires \{IO_AVAJE_VALIDATOR_HTTP_PLUGIN};` to compiled module-info.class");
          }
        }
      }
      default -> {
        // nothing to do
      }
    }
  }

  private void addServices(ModuleAttribute moduleAttribute, ModuleAttributeBuilder moduleBuilder, Stream<Path> servicesDir) {
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

    moduleAttribute.provides().stream()
        .filter(p -> !serviceMap.containsKey(p.provides().name().stringValue().replace("/", ".")))
        .forEach(moduleBuilder::provides);
    var log = getLog();
    serviceMap.forEach(
        (k, v) -> {
          var provides = ClassDesc.of(k);
          var with = v.stream().map(ClassDesc::displayName).collect(joining(","));
          log.info(STR."Adding `provides \{provides.displayName()} with \{with};` to compiled module-info.class");

          moduleBuilder.provides(ModuleProvideInfo.of(provides, v));
        });
  }
}
