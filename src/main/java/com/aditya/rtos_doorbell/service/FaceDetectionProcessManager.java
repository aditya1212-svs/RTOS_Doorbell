package com.aditya.rtos_doorbell.service;

import com.aditya.rtos_doorbell.dto.FaceBoundingBox;
import com.aditya.rtos_doorbell.dto.FaceDetectionWorkerResponse;
import com.aditya.rtos_doorbell.dto.FaceEmbeddingReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns one long-lived Python worker and its newline-delimited JSON protocol. */
@Component
public class FaceDetectionProcessManager implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(FaceDetectionProcessManager.class);

    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final String pythonCommand;
    private final String scriptPath;
    private final String workingDirectory;
    private final long startupTimeoutMs;
    private final long detectionRequestTimeoutMs;
    private final long recognitionRequestTimeoutMs;
    private final long maxImageBytes;
    private final Object processMonitor = new Object();
    private final Object writeMonitor = new Object();
    private final Map<String, CompletableFuture<FaceDetectionWorkerResponse>> pending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService restartExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "face-detector-restart");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean stopping = new AtomicBoolean();

    private volatile Process process;
    private volatile BufferedWriter input;
    private volatile boolean ready;
    private volatile boolean recognitionReady;
    private volatile boolean started;
    private volatile int restartAttempt;

    @Autowired
    public FaceDetectionProcessManager(ObjectMapper objectMapper,
                                       @Value("${face-detection.enabled:true}") boolean enabled,
                                       @Value("${face-detection.python-command:face-detector/.venv/bin/python}") String pythonCommand,
                                       @Value("${face-detection.script-path:face-detector/face_detector.py}") String scriptPath,
                                       @Value("${face-detection.working-directory:.}") String workingDirectory,
                                       @Value("${face-detection.max-image-bytes:5242880}") long maxImageBytes,
                                       @Value("${face-detection.startup-timeout-ms:10000}") long startupTimeoutMs,
                                       @Value("${face-detection.request-timeout-ms:5000}") long detectionRequestTimeoutMs,
                                       @Value("${face-recognition.request-timeout-ms:5000}") long recognitionRequestTimeoutMs) {
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.pythonCommand = pythonCommand;
        this.scriptPath = scriptPath;
        this.workingDirectory = workingDirectory;
        this.maxImageBytes = maxImageBytes;
        this.startupTimeoutMs = startupTimeoutMs;
        this.detectionRequestTimeoutMs = Math.max(1, detectionRequestTimeoutMs);
        this.recognitionRequestTimeoutMs = Math.max(1, recognitionRequestTimeoutMs);
    }

    /** Backward-compatible constructor for integrations that configure one worker timeout. */
    public FaceDetectionProcessManager(ObjectMapper objectMapper,
                                       boolean enabled,
                                       String pythonCommand,
                                       String scriptPath,
                                       String workingDirectory,
                                       long maxImageBytes,
                                       long startupTimeoutMs,
                                       long requestTimeoutMs) {
        this(objectMapper, enabled, pythonCommand, scriptPath, workingDirectory, maxImageBytes,
                startupTimeoutMs, requestTimeoutMs, requestTimeoutMs);
    }

    @Override
    public void start() {
        if (!enabled) {
            log.info("Face detector process disabled by configuration");
            started = true;
            return;
        }
        stopping.set(false);
        started = true;
        startProcess(true);
    }

    private void startProcess(boolean waitForReady) {
        synchronized (processMonitor) {
            if (stopping.get() || (process != null && process.isAlive())) return;
            try {
                Path directory = Path.of(workingDirectory).toAbsolutePath().normalize();
                Path script = directory.resolve(scriptPath).normalize();
                if (!script.toFile().isFile()) {
                    throw new FileNotFoundException("Face detector script not found: " + script);
                }
                ProcessBuilder builder = new ProcessBuilder(pythonCommand, script.toString());
                builder.directory(directory.toFile());
                builder.redirectErrorStream(false);
                builder.environment().put("FACE_DETECTOR_MAX_IMAGE_BYTES", Long.toString(maxImageBytes));
                Process startedProcess = builder.start();
                process = startedProcess;
                input = new BufferedWriter(new OutputStreamWriter(startedProcess.getOutputStream(), StandardCharsets.UTF_8));
                ready = false;
                recognitionReady = false;
                Thread stdout = new Thread(() -> readStdout(startedProcess), "face-detector-stdout");
                Thread stderr = new Thread(() -> readStderr(startedProcess), "face-detector-stderr");
                stdout.setDaemon(true);
                stderr.setDaemon(true);
                stdout.start();
                stderr.start();
                log.info("Face detector process started (pid={})", startedProcess.pid());
            } catch (Exception e) {
                ready = false;
                process = null;
                input = null;
                log.error("Unable to start face detector process: {}", e.getMessage());
                scheduleRestart();
                return;
            }
        }
        if (waitForReady) awaitReady();
    }

    private void awaitReady() {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(startupTimeoutMs);
        while (!isAvailable() && System.nanoTime() < deadline) {
            if (process == null || !process.isAlive()) break;
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (isAvailable()) log.info("Face detector process ready");
        else {
            log.warn("Face detector did not become ready within {} ms", startupTimeoutMs);
            if (process != null) handleProcessFailure();
        }
    }

    private void readStdout(Process ownedProcess) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(ownedProcess.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                handleResponse(line);
            }
        } catch (IOException e) {
            if (!stopping.get()) log.warn("Face detector stdout closed: {}", e.getMessage());
        } finally {
            handleProcessExit(ownedProcess);
        }
    }

    private void readStderr(Process ownedProcess) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(ownedProcess.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("worker ready")) ready = true;
                if (line.contains("recognition=available")) {
                    recognitionReady = true;
                    restartAttempt = 0;
                } else if (line.contains("recognition=unavailable")) {
                    recognitionReady = false;
                }
                log.info("[face-detector] {}", line);
            }
        } catch (IOException e) {
            if (!stopping.get()) log.debug("Face detector stderr closed: {}", e.getMessage());
        }
    }

    private void handleResponse(String line) {
        try {
            FaceDetectionWorkerResponse response = objectMapper.readValue(line, FaceDetectionWorkerResponse.class);
            String requestId = response.requestId();
            if (requestId == null || requestId.isBlank()) {
                log.warn("Ignoring face detector response without requestId");
                return;
            }
            CompletableFuture<FaceDetectionWorkerResponse> result = pending.remove(requestId);
            if (result == null) {
                log.warn("Ignoring face detector response for unknown requestId {}", requestId);
                return;
            }
            result.complete(response);
        } catch (Exception e) {
            log.warn("Malformed face detector response: {}", line);
            failPending(new FaceDetectionException(HttpStatus.BAD_GATEWAY,
                    "Face detector returned malformed output", e));
        }
    }

    private void handleProcessExit(Process ownedProcess) {
        synchronized (processMonitor) {
            if (process != ownedProcess) return;
            process = null;
            input = null;
            ready = false;
            recognitionReady = false;
        }
        if (!stopping.get()) {
            log.error("Face detector process crashed (exitCode={})", exitCode(ownedProcess));
            failPending(new FaceDetectionException(HttpStatus.BAD_GATEWAY, "Face detector process stopped"));
            scheduleRestart();
        }
    }

    private int exitCode(Process ownedProcess) {
        try { return ownedProcess.exitValue(); }
        catch (IllegalThreadStateException e) { return -1; }
    }

    private void scheduleRestart() {
        if (!enabled || stopping.get()) return;
        int attempt = Math.min(++restartAttempt, 5);
        long delay = Math.min(30, 1L << (attempt - 1));
        log.info("Face detector restart scheduled in {} seconds", delay);
        restartExecutor.schedule(() -> startProcess(false), delay, TimeUnit.SECONDS);
    }

    public FaceDetectionWorkerResponse detect(Path imagePath, int minNeighbors) {
        return execute(new FaceDetectionWorkerRequest(UUID.randomUUID().toString(), "detect",
                imagePath.toAbsolutePath().toString(), minNeighbors, null, null, null),
                detectionRequestTimeoutMs);
    }

    public FaceDetectionWorkerResponse embedding(Path imagePath, FaceBoundingBox face) {
        if (!isRecognitionAvailable()) {
            throw new FaceRecognitionException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Face recognition model is unavailable");
        }
        return execute(new FaceDetectionWorkerRequest(UUID.randomUUID().toString(), "embedding",
                imagePath.toAbsolutePath().toString(), null, face, null, null),
                recognitionRequestTimeoutMs);
    }

    public FaceDetectionWorkerResponse recognize(Path imagePath, int minNeighbors,
                                                 java.util.List<FaceEmbeddingReference> knownFaces,
                                                 double threshold) {
        return execute(new FaceDetectionWorkerRequest(UUID.randomUUID().toString(), "recognize",
                imagePath.toAbsolutePath().toString(), minNeighbors, null,
                knownFaces == null ? java.util.List.of() : knownFaces, threshold),
                recognitionRequestTimeoutMs);
    }

    private FaceDetectionWorkerResponse execute(FaceDetectionWorkerRequest workerRequest, long timeoutMs) {
        if (!enabled) {
            throw new FaceDetectionException(HttpStatus.SERVICE_UNAVAILABLE, "Face detection is disabled");
        }
        if (!isAvailable()) {
            throw new FaceDetectionException(HttpStatus.SERVICE_UNAVAILABLE, "Face detector process is unavailable");
        }
        String requestId = workerRequest.requestId();
        CompletableFuture<FaceDetectionWorkerResponse> result = new CompletableFuture<>();
        pending.put(requestId, result);
        try {
            String request = objectMapper.writeValueAsString(workerRequest);
            synchronized (writeMonitor) {
                if (!isAvailable()) throw new IOException("Face detector process is unavailable");
                input.write(request);
                input.newLine();
                input.flush();
            }
            log.debug("Face detection request submitted: {}", requestId);
            FaceDetectionWorkerResponse response = result.get(timeoutMs, TimeUnit.MILLISECONDS);
            if (response.error() != null && !response.error().isBlank()) {
                throw workerError(response.error(), workerRequest.operation());
            }
            return response;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FaceDetectionException(HttpStatus.SERVICE_UNAVAILABLE, "Face detection was interrupted", e);
        } catch (TimeoutException e) {
            handleHungProcess();
            throw new FaceDetectionException(HttpStatus.GATEWAY_TIMEOUT, "Face detector timed out", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof FaceDetectionException detectionException) throw detectionException;
            throw new FaceDetectionException(HttpStatus.BAD_GATEWAY, "Face detector failed", cause);
        } catch (IOException | RuntimeException e) {
            if (e instanceof FaceDetectionException detectionException) throw detectionException;
            handleProcessFailure();
            throw new FaceDetectionException(HttpStatus.BAD_GATEWAY, "Face detector is unavailable", e);
        } finally {
            pending.remove(requestId);
        }
    }

    private FaceDetectionException workerError(String error, String operation) {
        HttpStatus status = switch (error) {
            case "INVALID_IMAGE", "IMAGE_NOT_FOUND", "IMAGE_READ_ERROR", "IMAGE_TOO_LARGE" -> HttpStatus.BAD_REQUEST;
            case "INVALID_REQUEST", "REGISTRATION_REQUIRES_ONE_FACE", "NO_EMBEDDING",
                    "INVALID_FACE_BOX" -> HttpStatus.BAD_REQUEST;
            case "RECOGNITION_UNAVAILABLE" -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.BAD_GATEWAY;
        };
        if ("embedding".equals(operation) || "recognize".equals(operation)) {
            if ("RECOGNITION_UNAVAILABLE".equals(error)) {
                return new FaceRecognitionException(status, "Face recognition model is unavailable");
            }
            return new FaceRecognitionException(status, "Face recognition worker error: " + error);
        }
        return new FaceDetectionException(status, "Face detector error: " + error);
    }

    private void handleHungProcess() {
        log.error("Face detector process timed out; restarting it");
        handleProcessFailure();
    }

    private void handleProcessFailure() {
        Process owned;
        synchronized (processMonitor) {
            owned = process;
            process = null;
            input = null;
            ready = false;
            recognitionReady = false;
        }
        failPending(new FaceDetectionException(HttpStatus.BAD_GATEWAY, "Face detector process stopped"));
        if (owned != null && owned.isAlive()) owned.destroy();
        scheduleRestart();
    }

    private void failPending(FaceDetectionException exception) {
        pending.values().forEach(future -> future.completeExceptionally(exception));
        pending.clear();
    }

    public boolean isAvailable() {
        Process current = process;
        return enabled && ready && current != null && current.isAlive();
    }

    public boolean isRecognitionAvailable() {
        Process current = process;
        return enabled && ready && recognitionReady && current != null && current.isAlive();
    }

    public boolean isEnabled() { return enabled; }

    @Override public boolean isRunning() { return started; }
    @Override public boolean isAutoStartup() { return true; }
    @Override public int getPhase() { return Integer.MIN_VALUE; }

    @Override
    public void stop() {
        stopping.set(true);
        started = false;
        restartExecutor.shutdownNow();
        failPending(new FaceDetectionException(HttpStatus.SERVICE_UNAVAILABLE, "Face detector is shutting down"));
        Process owned;
        synchronized (processMonitor) {
            owned = process;
            process = null;
            input = null;
            ready = false;
            recognitionReady = false;
        }
        if (owned != null) {
            try { owned.getOutputStream().close(); } catch (IOException ignored) { }
            if (owned.isAlive()) {
                owned.destroy();
                try {
                    if (!owned.waitFor(2, TimeUnit.SECONDS)) owned.destroyForcibly();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    owned.destroyForcibly();
                }
            }
            log.info("Face detector process stopped");
        }
    }

    @Override public void stop(Runnable callback) { stop(); callback.run(); }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record FaceDetectionWorkerRequest(String requestId, String operation, String imagePath,
                                              Integer minNeighbors, FaceBoundingBox face,
                                              java.util.List<FaceEmbeddingReference> knownFaces,
                                              Double threshold) {}
}
