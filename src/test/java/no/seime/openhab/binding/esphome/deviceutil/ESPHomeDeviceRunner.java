package no.seime.openhab.binding.esphome.deviceutil;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ESPHomeDeviceRunner {
    private static final File TEST_HOME_DIRECTORY = new File("target/test-home");
    private static final File TEST_PLATFORMIO_CORE_DIRECTORY = new File("target/test-platformio");
    private static final File TEST_XDG_CACHE_HOME_DIRECTORY = new File("target/test-xdg-cache");
    private static final File USER_PLATFORMIO_DIRECTORY = new File(System.getProperty("user.home"), ".platformio");

    private final Logger logger = LoggerFactory.getLogger(ESPHomeDeviceRunner.class);

    private final ExecutorService executorService = Executors.newFixedThreadPool(1);
    private final File espDeviceConfigurationYamlFileName;
    private Process emulatorProcess;

    public ESPHomeDeviceRunner(File espDeviceConfigurationYamlFileName) {
        this.espDeviceConfigurationYamlFileName = espDeviceConfigurationYamlFileName;
    }

    public void compileAndRun() throws IOException {
        ensureRuntimeDirectories();
        ProcessBuilder compilationBuilder = new ProcessBuilder();
        compilationBuilder.redirectErrorStream(true);
        compilationBuilder.command("esphome", "compile", espDeviceConfigurationYamlFileName.getPath());
        compilationBuilder.environment().put("HOME", TEST_HOME_DIRECTORY.getAbsolutePath());
        compilationBuilder.environment().put("PLATFORMIO_CORE_DIR", TEST_PLATFORMIO_CORE_DIRECTORY.getAbsolutePath());
        compilationBuilder.environment().put("XDG_CACHE_HOME", TEST_XDG_CACHE_HOME_DIRECTORY.getAbsolutePath());
        Consumer<String> compilationListener = s -> {
            logger.info("{}", s);
        };
        Process compilationProcess = compilationBuilder.start();
        StreamGobbler streamGobblerCompilationProcess = new StreamGobbler(compilationProcess.getInputStream(),
                compilationListener);
        executorService.submit(streamGobblerCompilationProcess);

        try {

            int compilationExitCode = compilationProcess.waitFor();
            if (compilationExitCode != 0) {
                throw new RuntimeException("ESPHome compilation failed with exit code " + compilationExitCode);
            }

            // Now launch the compiled binary
            logger.info("ESPHome compilation completed successfully, now starting emulator");

            File parentFile = espDeviceConfigurationYamlFileName.getParentFile();
            File emulatorBinary = new File(parentFile, ".esphome/build/virtual/.pioenvs/virtual/program");
            if (!emulatorBinary.exists()) {
                throw new RuntimeException("Compiled binary not found in " + emulatorBinary);
            }

            ProcessBuilder emulatorBuilder = new ProcessBuilder(emulatorBinary.toString());
            emulatorBuilder.redirectErrorStream(true);
            emulatorProcess = emulatorBuilder.start();
            emulatorProcess.onExit().thenRun(() -> {
                logger.info("ESPHome emulator exited");
            });

            // For some reason cannot get console output from the emulator, so just wait a second before proceeding.
            // Startup is quick
            Thread.sleep(2000);
            logger.info("ESPHome emulator started successfully");

        } catch (InterruptedException e) {
            logger.error("Interrupted while waiting for ESPHome to start", e);
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for ESPHome to start", e);

        }
    }

    public void shutdown() throws InterruptedException {
        // shutdown the executor service now
        if (emulatorProcess != null)
            emulatorProcess.destroy();
        executorService.shutdown();
        executorService.awaitTermination(5, TimeUnit.SECONDS);
    }

    private void ensureRuntimeDirectories() throws IOException {
        createDirectory(TEST_HOME_DIRECTORY);
        createDirectory(TEST_PLATFORMIO_CORE_DIRECTORY);
        createDirectory(TEST_XDG_CACHE_HOME_DIRECTORY);
        linkPlatformIOCache("platforms");
        linkPlatformIOCache("packages");
    }

    private void createDirectory(File directory) throws IOException {
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Could not create directory " + directory.getAbsolutePath());
        }
    }

    private void linkPlatformIOCache(String directoryName) throws IOException {
        Path source = USER_PLATFORMIO_DIRECTORY.toPath().resolve(directoryName);
        Path target = TEST_PLATFORMIO_CORE_DIRECTORY.toPath().resolve(directoryName);

        if (!Files.exists(source)) {
            return;
        }

        if (Files.isDirectory(target) && isEmptyDirectory(target)) {
            Files.delete(target);
        } else if (Files.exists(target)) {
            return;
        }

        Files.createSymbolicLink(target, source);
    }

    private boolean isEmptyDirectory(Path directory) throws IOException {
        try (var contents = Files.list(directory)) {
            return contents.findAny().isEmpty();
        }
    }

    private static class StreamGobbler implements Runnable {
        private final InputStream inputStream;
        private final Consumer<String> consumer;

        public StreamGobbler(InputStream inputStream, Consumer<String> consumer) {
            this.inputStream = inputStream;
            this.consumer = consumer;
        }

        @Override
        public void run() {
            new BufferedReader(new InputStreamReader(inputStream)).lines().forEach(consumer);
        }
    }
}
