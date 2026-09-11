package se.swedishpolls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

/** Verifies Spring config-data loading without starting the production application wiring. */
class EnvironmentFileTest {
  @Test
  void loadsDotenvValuesAndLetsProcessEnvironmentWin(@TempDir Path directory) throws Exception {
    Files.writeString(
        directory.resolve(".env"),
        "DATABASE_PASSWORD=from-file\nAPP_PORT=9010\nDATABASE_PORT=55432\n");
    final ProcessBuilder builder =
        new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp",
            System.getProperty("surefire.test.class.path"),
            EnvironmentFileTest.class.getName(),
            "--spring.main.banner-mode=off",
            "--logging.level.root=OFF",
            "--spring.docker.compose.enabled=false");
    builder.directory(directory.toFile());
    builder.redirectErrorStream(true);
    builder.environment().clear();
    builder.environment().put("APP_PORT", "9020");
    final Process process = builder.start();
    final boolean finished = process.waitFor(30, TimeUnit.SECONDS);
    if (!finished) process.destroyForcibly();
    assertTrue(finished, "Spring environment probe did not exit");
    final List<String> output = process.inputReader().readAllLines();
    assertEquals(0, process.exitValue(), output.toString());
    assertEquals(
        List.of("9020", "jdbc:postgresql://localhost:55432/swedishpolls", "from-file"), output);
  }

  public static void main(String[] args) throws IOException {
    final SpringApplication application = new SpringApplication(EnvironmentFileTest.class);
    application.setWebApplicationType(WebApplicationType.NONE);
    try (final ConfigurableApplicationContext context = application.run(args)) {
      System.out.println(context.getEnvironment().getProperty("server.port"));
      System.out.println(context.getEnvironment().getProperty("spring.datasource.url"));
      System.out.println(context.getEnvironment().getProperty("DATABASE_PASSWORD"));
    }
  }
}
