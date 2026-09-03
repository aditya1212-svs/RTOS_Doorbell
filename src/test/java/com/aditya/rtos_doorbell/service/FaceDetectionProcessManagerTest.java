package com.aditya.rtos_doorbell.service;

import com.aditya.rtos_doorbell.dto.FaceDetectionWorkerResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class FaceDetectionProcessManagerTest {
    @TempDir
    Path directory;

    @Test
    void reusesOneWorkerForSequentialRequests() throws Exception {
        Path script = directory.resolve("worker.sh");
        Files.writeString(script, "#!/bin/sh\n" +
                "echo 'face detector worker ready' >&2\n" +
                "while IFS= read line; do\n" +
                "  id=$(printf '%s' \"$line\" | awk -F '\"requestId\":\"' '{print $2}' | cut -d '\"' -f1)\n" +
                "  printf '{\"requestId\":\"%s\",\"facesDetected\":0,\"faces\":[],\"frameWidth\":10,\"frameHeight\":10}\\n' \"$id\"\n" +
                "done\n");
        Path image = directory.resolve("frame.jpg");
        Files.write(image, new byte[]{1});
        FaceDetectionProcessManager manager = new FaceDetectionProcessManager(new ObjectMapper(), true,
                "sh", script.getFileName().toString(), directory.toString(), 5_242_880, 2_000, 1_000);
        try {
            manager.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!manager.isAvailable() && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(manager.isAvailable());

            FaceDetectionWorkerResponse first = manager.detect(image, 5);
            FaceDetectionWorkerResponse second = manager.detect(image, 5);
            assertEquals(0, first.facesDetected());
            assertEquals(0, second.facesDetected());
            assertEquals(10, second.frameWidth());
        } finally {
            manager.stop();
        }
    }

    @Test
    void restartsWorkerAfterUnexpectedExit() throws Exception {
        Path script = directory.resolve("crashing-worker.sh");
        Files.writeString(script, "#!/bin/sh\n" +
                "count=0\n" +
                "[ -f worker.count ] && count=$(cat worker.count)\n" +
                "count=$((count + 1))\n" +
                "printf '%s' \"$count\" > worker.count\n" +
                "echo 'face detector worker ready' >&2\n" +
                "if [ \"$count\" -eq 1 ]; then read line; exit 42; fi\n" +
                "while IFS= read line; do\n" +
                "  id=$(printf '%s' \"$line\" | awk -F '\"requestId\":\"' '{print $2}' | cut -d '\"' -f1)\n" +
                "  printf '{\"requestId\":\"%s\",\"facesDetected\":0,\"faces\":[],\"frameWidth\":10,\"frameHeight\":10}\\n' \"$id\"\n" +
                "done\n");
        Path image = directory.resolve("frame.jpg");
        Files.write(image, new byte[]{1});
        FaceDetectionProcessManager manager = new FaceDetectionProcessManager(new ObjectMapper(), true,
                "sh", script.getFileName().toString(), directory.toString(), 5_242_880, 2_000, 1_000);
        try {
            manager.start();
            assertTrue(manager.isAvailable());
            assertThrows(FaceDetectionException.class, () -> manager.detect(image, 5));

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
            while (!manager.isAvailable() && System.nanoTime() < deadline) Thread.sleep(20);
            assertTrue(manager.isAvailable());
            assertEquals(0, manager.detect(image, 5).facesDetected());
        } finally {
            manager.stop();
        }
    }

    @Test
    void detectsThroughTheRealPythonWorkerWhenInstalled() throws Exception {
        Path projectRoot = Path.of("").toAbsolutePath().normalize();
        Path python = projectRoot.resolve("face-detector/.venv/bin/python");
        Path script = projectRoot.resolve("face-detector/face_detector.py");
        assumeTrue(Files.isRegularFile(python) && Files.isRegularFile(script),
                "face-detector virtualenv is not installed");

        Path image = directory.resolve("frame.jpg");
        BufferedImage frame = new BufferedImage(320, 240, BufferedImage.TYPE_INT_RGB);
        assertTrue(ImageIO.write(frame, "jpg", image.toFile()));

        FaceDetectionProcessManager manager = new FaceDetectionProcessManager(new ObjectMapper(), true,
                python.toString(), script.toString(), projectRoot.toString(), 5_242_880, 10_000, 5_000);
        try {
            manager.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!manager.isAvailable() && System.nanoTime() < deadline) Thread.sleep(20);
            assertTrue(manager.isAvailable());

            // Regression test: the worker must accept the exact JSON this manager serializes
            // (optional record fields), otherwise /api/face/detect fails with 400 INVALID_REQUEST.
            FaceDetectionWorkerResponse response = manager.detect(image, 5);
            assertNull(response.error());
            assertEquals(320, response.frameWidth());
            assertEquals(240, response.frameHeight());
            assertNotNull(response.faces());
        } finally {
            manager.stop();
        }
    }
}
