package com.logscope.architecture;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** 기능별 패키지와 계층 경계가 다시 평탄화되지 않게 하는 구조 회귀 검사다. */
class BackendArchitectureTests {
  private List<Path> sources() throws Exception {
    try (var main = Files.walk(Path.of("src/main/java/com/logscope"));
        var sample = Files.walk(Path.of("sample-was/src/main/java/com/logscope"))) {
      return java.util.stream.Stream.concat(main, sample)
          .filter(p -> p.toString().endsWith(".java"))
          .toList();
    }
  }

  @Test
  void featureClassesHaveExplicitLayerPackagesAndConfigurationLivesUnderConfig() throws Exception {
    var allowed =
        Set.of(
            "controller",
            "service",
            "repository",
            "client",
            "domain",
            "dto",
            "validation",
            "config",
            "exception",
            "support",
            "logging");
    for (var path : sources()) {
      String source = Files.readString(path);
      var matcher = Pattern.compile("package ([a-zA-Z0-9_.]+);").matcher(source);
      assertThat(matcher.find()).isTrue();
      String pkg = matcher.group(1);
      if (path.getFileName().toString().endsWith("Application.java")) continue;
      var pieces = pkg.split("\\.");
      assertThat(pieces).hasSizeGreaterThanOrEqualTo(4);
      assertThat(allowed).contains(pieces[3]);
      if (source.contains("@Configuration(")) assertThat(pkg).endsWith(".config");
      assertThat(path.toString().replace('\\', '/'))
          .endsWith(pkg.replace('.', '/') + "/" + path.getFileName());
    }
  }

  @Test
  void controllersDoNotReachPersistenceClientsOrSchedulingDirectly() throws Exception {
    for (var path : sources()) {
      String normalized = path.toString().replace('\\', '/');
      if (!normalized.contains("/controller/")) continue;
      String source = Files.readString(path);
      assertThat(source)
          .doesNotContain(
              "com.logscope.log.repository",
              "com.logscope.log.client",
              "org.springframework.jdbc",
              "@Scheduled(");
    }
  }

  @Test
  void repositoriesAndClientsDoNotDependOnControllersOrBusinessServices() throws Exception {
    for (var path : sources()) {
      String normalized = path.toString().replace('\\', '/');
      if (!normalized.contains("/repository/") && !normalized.contains("/client/")) continue;
      assertThat(Files.readString(path))
          .doesNotContain(
              "com.logscope.log.controller", "com.logscope.log.service", "jakarta.servlet");
    }
  }
}
