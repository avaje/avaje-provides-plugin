# Avaje Build Maven plugin

Maven plugin that post-processes modular applications `module-info.class` files after compilation to add all the required `provides` clauses for all services registered under `META-INF/services` as well as adding `requires` for avaje specific inject plugins as needed.

## How to use

### 1. Create a `.mvn/jvm.config` file
This plugin uses the JDK 22 [Class-File API](https://openjdk.org/jeps/457). As the feature is still in preview, create a `.mvn/jvm.config` file and add `--enable-preview` so that the maven JVM will run with preview features.

### 2. Add the plugin to your pom.xml

```xml
<plugin>
  <groupId>io.avaje</groupId>
  <artifactId>avaje-build-maven-plugin</artifactId>
  <version>${version}</version>
  <executions>
  	<execution>
  		<phase>process-sources</phase>
  		<goals>
  			<goal>provides</goal>
  			<goal>module-spi</goal>
  		</goals>
  	</execution>
  </executions>
</plugin>
```
