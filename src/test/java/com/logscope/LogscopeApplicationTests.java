package com.logscope;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class LogscopeApplicationTests {

  @Autowired JdbcTemplate jdbc;
  @Autowired Flyway flyway;

  @Test
  void contextLoads() {
    assertThat(jdbc.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
    assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("1");
    assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
    flyway.migrate();
    assertThat(flyway.info().pending()).isEmpty();
  }
}
