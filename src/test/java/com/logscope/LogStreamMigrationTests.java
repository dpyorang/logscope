package com.logscope;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class LogStreamMigrationTests {
  @Container
  static MySQLContainer mysql = new MySQLContainer(DockerImageName.parse("mysql:8.4.13"));

  @Test
  void upgradesExistingLogsAndPreservesCursorStateAcrossRestart() throws Exception {
    Flyway.configure()
        .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
        .target("2")
        .load()
        .migrate();
    try (var connection =
            DriverManager.getConnection(
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        var sql = connection.createStatement()) {
      sql.executeUpdate(
          "INSERT INTO log_events (event_id,occurred_at,service_name,instance_id,level,message) VALUES ('00000000-0000-4000-8000-000000000002','2026-10-28 01:00:00.000','sample-was','was-1','INFO','Synthetic newer'),('00000000-0000-4000-8000-000000000001','2026-10-28 00:00:00.000','sample-was','was-2','ERROR','Synthetic older')");
      var current =
          Flyway.configure()
              .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
              .load();
      current.migrate();
      try (var rs =
          sql.executeQuery("SELECT stream_sequence FROM log_events ORDER BY occurred_at")) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getLong(1)).isEqualTo(1);
        assertThat(rs.next()).isTrue();
        assertThat(rs.getLong(1)).isEqualTo(2);
      }
      String epoch;
      try (var rs =
          sql.executeQuery("SELECT epoch,last_sequence FROM log_stream_state WHERE id=1")) {
        assertThat(rs.next()).isTrue();
        epoch = rs.getString(1);
        assertThat(rs.getLong(2)).isEqualTo(2);
      }
      // New Flyway instance simulates application startup, without resetting replay state.
      Flyway.configure()
          .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
          .load()
          .migrate();
      try (var rs =
          sql.executeQuery("SELECT epoch,last_sequence FROM log_stream_state WHERE id=1")) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getString(1)).isEqualTo(epoch);
        assertThat(rs.getLong(2)).isEqualTo(2);
      }
    }
  }
}
